using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Identity.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class DeviceQuietDuringFocus : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<bool>(
                name: "quiet_during_focus",
                schema: "identity",
                table: "devices",
                type: "boolean",
                nullable: false,
                defaultValue: false);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "quiet_during_focus",
                schema: "identity",
                table: "devices");
        }
    }
}
