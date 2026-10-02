using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Identity.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class DeviceSession : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<Guid>(
                name: "session_id",
                schema: "identity",
                table: "devices",
                type: "uuid",
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "IX_devices_push_token",
                schema: "identity",
                table: "devices",
                column: "push_token")
                .Annotation("Npgsql:IndexMethod", "hash");

            migrationBuilder.CreateIndex(
                name: "IX_devices_session_id",
                schema: "identity",
                table: "devices",
                column: "session_id");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_devices_push_token",
                schema: "identity",
                table: "devices");

            migrationBuilder.DropIndex(
                name: "IX_devices_session_id",
                schema: "identity",
                table: "devices");

            migrationBuilder.DropColumn(
                name: "session_id",
                schema: "identity",
                table: "devices");
        }
    }
}
